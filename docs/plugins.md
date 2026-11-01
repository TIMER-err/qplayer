# QPlayer JavaScript plugin ABI v1

QPlayer core does not contain an online music source. A source is a user-installed
`.qplug` archive executed by the embedded Rhino engine. This document describes
the implementation for QPlayer **1.9.7**. The ABI remains `1.0`.
This baseline is not a claim that every interface below first appeared in 1.9.7.

`apiVersion` must have the same major version as the host ABI and a minor version
no greater than the host's. `minHostVersion`, when set, must not exceed the running
QPlayer version. The host compares up to four numeric version components and
ignores `-`/`+` suffixes. Choose the minimum host version your plugin actually
supports; ABI `1.0` alone does not establish availability of every current feature.

Unless a byte limit is stated, string limits below count Java UTF-16 code units
(`String.length()`), not UTF-8 bytes or Unicode code points.

## Identity

Plugins return native IDs such as `2668056312`. The host owns canonical IDs and
converts them to:

```text
<provider>:<kind>:<percent-encoded-native-id>
```

Kinds are `song`, `album`, `artist`, `playlist`, and `user`. Native IDs are
limited to 2048 UTF-8 bytes and may not contain control characters. A plugin only
receives native IDs belonging to itself. It must never manufacture another
provider's canonical ID. Persisted queues, caches, playlist context, and UI
navigation all use canonical IDs.

## Package layout

```text
plugin.json
src/main.js
assets/icon.png
META-INF/qplayer-files.json
META-INF/qplayer.sig
```

`qplayer-files.json` maps every package file except the hash manifest itself and
`qplayer.sig` to a SHA-256 digest (64 hexadecimal characters; lowercase is
recommended). This includes `plugin.json`, JavaScript and resources. `qplayer.sig`
is the Base64 DER ECDSA/SHA-256 signature of the exact hash-manifest bytes.
Catalog sources use pinned P-256 publisher keys and must declare the expected
plugin ID. A manually imported package without a trusted publisher signature
requires the host's code-execution warning; a signature file alone does not make
it trusted.

The archive may contain at most 512 files, each at most 16 MiB, with at most
64 MiB of uncompressed file content. Installation initializes the staged runtime
with host calls denied and checks every advertised handler. Keep initialization
free of required network, storage or playback calls. Installed files are re-hashed
before each activation; added or modified files prevent activation. Resource
directories such as `assets/` or `ui/` do not give a plugin executable QML support.

Minimal manifest:

```json
{
  "schemaVersion": 1,
  "id": "example",
  "name": "Example Source",
  "version": "1.0.0",
  "apiVersion": "1.0",
  "minHostVersion": "1.9.6",
  "entry": "src/main.js",
  "capabilities": ["searchSongs"],
  "permissions": ["network"],
  "networkDomains": ["api.example.com", "*.cdn.example.com"],
  "networkMethods": ["GET", "POST"],
  "ui": []
}
```

All paths are relative, cannot contain `..`, backslashes, schemes, or absolute
segments. IDs, capabilities, permissions, methods, domains, and UI contribution
IDs are validated before extraction or execution.

The example uses the documentation baseline as its declared minimum, not a
historical introduction version. Optional manifest fields include `publisher`
(display metadata) and `icon` (a package-relative resource path). The default
entry is `main.js`. `networkMethods` accepts `GET`, `POST`, `PUT`, `PATCH`,
`DELETE`, and `HEAD`; an empty list defaults HTTP requests to `GET` and `POST`.
`*.example.com` grants subdomains, not the bare `example.com`.

Permission names are `network`, `clearTextNetwork`, `localNetwork`, `credentials`,
`webAuth`, `clipboard`, `openUrl`, `playbackRead`, `playbackControl`, `queueWrite`,
`notifications`, `customUi`, `backgroundTimers`, and `files.save`. `openUrl` is
accepted in manifests but has no implemented public host call. Declaring
`networkDomains` requires `network`; `login` requires `credentials`; nonempty
`ui` requires `customUi`. All requested permissions must be approved before
activation.

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
        return { items: [], nextCursor: "" };
      });
    }
  }
};
```

Handlers may return a value or a Promise. Each plugin has one serialized actor
thread; Promise continuations run there too. This serializes JavaScript execution,
not the lifetime of asynchronous operations, so handlers can interleave while
awaiting host calls. Initialization and handler calls have a 90-second deadline;
the instruction observer interrupts long-running JavaScript. Each module is
limited to 4 MiB. Values crossing the runtime boundary have a maximum nesting
depth of 32, 500,000 conversion nodes, and 24 Mi characters of accumulated strings.

`qplayer.pluginId` exposes the plugin ID. Export `{handlers: {...}}` through
CommonJS, or pass that object to `qplayer.register(...)`. `require("./helper")`
appends `.js`; relative parent paths are allowed only while they remain inside
the package. Java classes, `Packages`, Node.js/native modules, browser DOM,
arbitrary filesystem access, and a process-wide QML context are not available.
There is no public `setTimeout`/`setInterval` or `timers.*` service. Use
`backgroundTick` for host-driven periodic work.

## Capabilities and handlers

Each advertised capability requires a same-named function in `handlers`, including
accepted names that the host does not currently invoke. Arguments below use
native IDs, not canonical IDs.

| Capability | Arguments → result |
|---|---|
| `searchSongs` | `{query,cursor,limit}` → song page |
| `searchAlbums`, `searchArtists` | `{query,cursor,limit}` → album/artist page |
| `searchPlaylists` | `{query,cursor,limit}` → playlist page; requires QPlayer 1.9.7 |
| `hotSearch` | `{}` → array of strings; host keeps at most 50 nonempty trimmed entries |
| `home` | `{limit}` → `{songs,playlists,sections}` |
| `songDetails` | `{ids}` (at most 500) → song array (at most 500) |
| `playlistDetails` | `{id}` → Playlist |
| `artistDetails`, `albumDetails` | `{id}` → Artist/Album |
| `recent` | `{limit}` (1–500) → song array (at most 500) |
| `userPlaylists` | `{limit}` (1–500) → playlist array (at most 500) |
| `resolveStream` | `{id,quality}` → StreamDescriptor |
| `lyrics` | `{id}` → `{assets:[...]}` |
| `account` | `{}` → AccountProfile |
| `login` | `{operation,...}` → methods, challenge or logout result; see below |
| `like` | `{operation:"list"}` → native song ID array; `{operation:"set",id,liked}` → success |
| `playlistMutation` | `{operation,playlistId,songIds,name?}` → success, or the separate `create` form below |
| `playlistReorder` | `{playlistId,songIds}` → success |
| `playlistCover` | `{playlistId,imageBase64,filename,mimeType}` → success |
| `scrobble` | `{id,playedMs,durationMs,completed}` → ignored result |
| `heartRecommendation` | `{songId,playlistId,limit}` (1–500) → song array (at most 500) |
| `share` | `{id,kind}` → nonempty string used as the share URL/text |
| `matchSong` | Accepted capability, but no current host caller or defined argument/result parser |

For boolean mutations, return `true`/`false` or `{success:true}`/`{success:false}`.
The `like` list accepts fewer than 100,000 distinct nonempty IDs; reaching
100,000 distinct entries fails the response. `playlistMutation.operation` is
`add`, `remove`, `subscribe`, `unsubscribe`, or `delete`, with at most 1,000 song
IDs. `name` is sent when supplied by the caller. Creation instead receives
`{operation:"create",name,private}` and returns `{id:"native-playlist-id"}` or the
native ID string. An absent ID becomes an empty host result; do not return a
boolean or a generic success object for creation.

`playlistCover` is separate from `playlistMutation` so the host can hide the
action when unsupported. The host sends the chosen image unchanged as Base64,
with at most 8 MiB before encoding. `filename` is a display name, not a path;
`mimeType` can be empty when unknown. Cropping and provider-specific uploads
belong to the plugin.

`playlistReorder` is available for owned playlists. `songIds` contains the
**complete** new order, not a from/to pair. The host sends one call when the
gesture ends and rejects empty orders, duplicate IDs, or more than 10,000 IDs.
If the provider's reorder endpoint also rewrites metadata, preserve its existing
name, description, cover and tags. Fetch required metadata before the request;
do not submit a partial form that clears omitted fields.

`home` accepts optional `songs` (100), `playlists` (100), and `sections` (6).
Each section is `{title,playlists}` with at most 30 playlists. Empty sections are
omitted. The `limit` argument (1–100) reflects the home-page playlist setting;
the parser's response caps are independent of it. A secondary service entry
also invokes `home` with `{operation:"recommendSongs"}` and expects a song array
of at most 500; this differs from the normal object response.
`heartRecommendation` always receives both a song and a playlist from the same
provider. `share.kind` is `song`, `album`, `artist`, `playlist`, or `user`.
No host parser restricts its nonempty result to a URL.

### Pages and entities

Search calls receive a string `query`, a string `cursor` (initially `""`), and
`limit` clamped to 1–100. Return `{items:[...],nextCursor:"..."}`. Each response
can contain at most 200 items and an 8,192-character cursor. An empty or missing
`nextCursor` ends pagination. `hasMore` is not read; returning it cannot request
another page. The host does not truncate an oversized response to `limit`.

`searchPlaylists` is optional and requires QPlayer **1.9.7** or newer. A plugin
that advertises it must set `minHostVersion` to at least `1.9.7`; older hosts do
not recognize this capability. Existing search and detail handlers are unchanged.
Return the existing Playlist DTO in `items`; summary entries can omit `songs`.
The host qualifies each native playlist ID as `provider:playlist:nativeId` and
each nonempty `owner.id` as `provider:user:nativeId` (with native IDs percent-encoded).
Opening a result uses `playlistDetails` on that provider, not a separate search
detail handler.

Playlist search queries all enabled providers that declare the capability, with
the primary provider first. Results are deduplicated by qualified playlist ID.
Each next-page cursor must advance; repeating the requested cursor ends pagination.
Successful small or duplicate-only pages can trigger another request to fill the viewport.

The following are the fields read from plugin JSON. Host-side DTO aliases such
as `coverUrl`, `name` on Song, or `artistIdsCsv` are not extra wire fields.
IDs should be strings to avoid JavaScript number precision loss. Required
`id` values are nonempty native IDs and are qualified by the host.

| Entity | Fields read by the host |
|---|---|
| Song | Required `id`, `title`; optional `durationMs`, `artworkUrl`, `artworkThumbUrl`, `isrc`, `playable`, `trial`, `restricted`, `artists:[{id,name}]`, `album:{id,name}` |
| Playlist | Required `id`, `name`; optional `description`, artwork URLs, `trackCount`, `playCount`, `subscribed`, `owned`, `mutable`, `deletable`, `owner:{id,name}`, `songs` |
| Album | Required `id`, `name`; optional artwork URLs, `publishTimeMs`, `description`, `trackCount`, `artists:[{id,name}]`, `songs` |
| Artist | Required `id`, `name`; optional artwork URLs, `description`, `albumCount`, `songCount`, `songs`, `albums` |

Labels and entity names are limited to 4,096 characters; descriptions to 1 Mi
characters. Song `durationMs` is 0–31 days in milliseconds (default 0);
`playable` defaults to true, while `trial` and `restricted` default to false.
At most 64 artist references are read per song or album. Unnamed artist credits
and unnamed song-album references are omitted. Named references require IDs.

Playlist `mutable` and `deletable` default to `owned` (itself false by default).
Its `owner.id` may be empty. `trackCount` and `playCount` are nonnegative.
Detail lists allow 20,000 playlist songs, 5,000 album songs, and 1,000 artist
songs/albums. Album `trackCount` and artist `albumCount` are 0–1,000,000;
artist `songCount` is 0–10,000,000. Other omitted counts default to zero.
`publishTimeMs` is read as a number, defaulting to zero.

`artworkThumbUrl` is a list-size alternative to `artworkUrl` and falls back to
the full URL when absent or disallowed. Disallowed artwork URLs become empty.
Ordinary song lists omit malformed songs instead of rejecting the whole list;
`queue.replace` rejects malformed entries. Other response-shape and count
errors can reject the entire call.

### Streams

Example response from `resolveStream`:

```js
return {
  url: "https://cdn.example.com/song.mp3",
  headers: { "Referer": "https://api.example.com/" },
  mimeType: "audio/mpeg", expiresAtMs: 0,
  trial: false, cacheable: true, renditionId: args.id
};
```

`url` is required and must pass the plugin's network grant. `headers` is optional
and allows at most 64 entries. Names must be HTTP tokens of 1–128 characters;
values allow at most 8,192 characters and no CR/LF. `Host`, `Content-Length`,
and `Connection` are ignored. `mimeType` defaults to `""`, `expiresAtMs` to 0,
`trial` to false, and `cacheable` to true. `expiresAtMs` is an absolute
millisecond timestamp; the parser does not reject expired/negative values.
Optional `renditionId` is a native song ID that the host qualifies.

`quality` defaults to `standard` when the service caller supplies no value.
The host's playback/download choices use `standard`, `higher`, `exhigh`,
`lossless`, and `hires`; the service forwards a non-null quality string without
enum validation. Set `trial` and `cacheable` according to the actual rendition,
not merely the requested quality.

### Lyrics

Return raw text; the host parses timing and renders lyrics:

```js
return { assets: [
  { format: "yrc", role: "original", text: "[0,1000](0,1000,0)Hello" },
  { format: "lrc", role: "translation", text: "[00:00.00]你好" },
  { format: "lrc", role: "romanization", text: "[00:00.00]Hello" }
] };
```

Only include nonempty assets. `assets:[]` means no lyrics. There can be at most
12 assets, each with nonempty `text` of at most 4 Mi characters.

| Field | Accepted plugin values |
|---|---|
| `format` | `lrc`, `yrc`, `ttml` (normalized to lowercase) |
| `role` | `original`, `translation`, `romanization` (case-sensitive; omitted/empty defaults to `original`) |

Asset order matters. In the normal one-asset-per-role response, the host selects
the first original, first translation, and first romanization. It does **not**
prefer YRC or TTML over LRC. Put the desired original first; an empty parse does
not cause a retry with another original. The consumer's fallback branch can
select a repeated translation/romanization as the base if no base has yet been
selected, so do not use duplicate roles as format alternatives.

Translation and romanization assets are parsed using their own `format`, then
attached to the closest original line by start time. For TTML, the parser can
also read translation/romanization embedded in the document; separate sidecars
can replace those line fields. Supply timed sidecars, not an unstructured
translation string.

Local-file support is not the plugin wire contract: `.lys` is parsed by the
local-file path, but `format:"lys"` is rejected in plugin assets. `toml` and
`plain` are also rejected. Internal content parsing includes QRC, ESLRC and
word-time LRC branches and recognizes `translated`/`romaji` role aliases, but
the plugin response validator does not accept those format/role names.
Download metadata uses a separate rule: it takes the first nonempty explicit
original asset's raw text, or the first other nonempty asset if none exists,
without format preference or lyric composition.

For implementation details, see
[`PluginProviderService`](../player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginProviderService.java)
and [`LyricParser`](../player-core/src/main/java/dev/t1m3/qplayer/lyric/LyricParser.java).
Provider-specific features such as Listen Together use ordinary plugin handlers,
declarative UI and generic host services, not a host room protocol.
The deprecated `listenTogether` capability is still accepted for existing
packages, but QPlayer does not invoke it or expose a corresponding service.

## Host calls

Calls are asynchronous: `qplayer.call(method, arguments)` normally returns a
Promise. Permission/argument checks in the JavaScript gate can throw before a
Promise is created; dispatcher/service failures reject it. A permission prefix
in the runtime gate does not imply that every method with that prefix exists.

### Storage and network

| Method | Permission | Arguments → result |
|---|---|---|
| `storage.get` | none | `{key}` → stored JSON-like value, or `null` |
| `storage.put` | none | `{key,value}` → `true` |
| `storage.delete` | none | `{key}` → whether a file was deleted |
| `credentials.get` | `credentials` | `{key}` → string, or `null` |
| `credentials.put` | `credentials` | `{key,value}` (nonempty string) → `true` |
| `credentials.delete` | `credentials` | `{key}` → whether a credential was deleted |
| `http.request` | `network` | request object → `{status,finalUrl,body,headers,setCookies,bodyBase64?}` |

Storage and credential keys match `[A-Za-z0-9][A-Za-z0-9._-]{0,127}` and are
hashed within the plugin namespace. The 1 MiB storage limit applies to each
serialized record, including its `key`/`value` envelope, not the whole plugin.
Credential values are limited to 1 MiB of UTF-8 bytes.

`http.request` accepts required `url`, optional `method` (default `GET`),
`headers`, `timeoutMs` (default 10,000; clamped to 1,000–30,000),
`followRedirects` (default true), and `includeBase64` (default false).
Choose at most one request body:

- `body`: nonempty text encoded as UTF-8.
- `bodyBase64`: nonempty Base64 string decoded to bytes.
- `multipart:{fields,file}`: up to 32 text fields (4,096 characters each) and
  one optional file `{field,filename,mimeType,bodyBase64}`. File defaults are
  `field:"file"`, `filename:"upload.bin"`, `mimeType:"application/octet-stream"`;
  file bytes must be nonempty. Field names match
  `[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}`. The host generates the multipart content
  type unless a `Content-Type` header was supplied.

Request and response bodies are limited to 16 MiB. HTTP headers use the same
64-entry/token/value limits as streams, with `Host`, `Content-Length`, and
`Connection` ignored. `body` in a response is always a UTF-8 string;
`bodyBase64` is included only when `includeBase64:true`. `headers` maps names
to joined string values, and `setCookies` preserves individual `Set-Cookie`
headers across followed redirects. The host does not maintain a plugin cookie
jar; persist and send provider cookies explicitly.

HTTP errors such as 404 are response statuses, not automatically rejected
Promises. Redirects follow at most five hops and recheck URL grants; 303, or
301/302 after POST, changes the method to GET. `followRedirects:false` returns
the first response, including its `Location`. A retained QQ ptlogin compatibility
path returns its QR-success redirect as a synthetic `ptuiCB(...)` response body
instead of following that handoff.

The host negotiates gzip unless the plugin sets `Accept-Encoding`. It returns
the decoded body and removes `Content-Encoding`/`Content-Length` from reported
headers, enforcing the same 16 MiB decoded limit. With a plugin-supplied
`Accept-Encoding`, bytes remain raw; use `includeBase64` and
`compression.gunzip` as needed.

HTTP(S) URLs must match `networkDomains`. HTTP also requires
`clearTextNetwork`. Private/local resolved addresses require `localNetwork`.
DNS screening is a separate lookup from the transport connection, not a pinned
resolver; see [Plugin security](plugin-security.md) for that boundary.
Host-fetched artwork and `files.save` recheck the grant on every redirect.
For cached audio and host song downloads, only the initial URL must match
`networkDomains`; later CDN hops still receive scheme/address checks.
Platform playback backends validate the initial URL, but redirects inside their
media stack follow that platform's networking model.

### Cryptography and compression

These calls need no permission. Except `crypto.sha256` and `crypto.modPow`, they
return a string encoded with `outputEncoding` (`base64` by default; also `hex`
or `utf8`). A `data` string uses `dataEncoding:"utf8"` by default; key material
uses Base64 by default. For `key`, `iv`, `aad`, `scalar`, and `point`, set the
corresponding `<field>Encoding` to `base64`, `hex`, or `utf8` when needed.
Decoded `data` is limited to 8 MiB.

| Method | Arguments |
|---|---|
| `crypto.sha256` | `{value}` → lowercase hex SHA-256 of UTF-8 text |
| `crypto.digest` | `{algorithm,data,dataEncoding?,outputEncoding?}`; `SHA-256`, `SHA-1`, or `MD5` |
| `crypto.random` | `{length?,outputEncoding?}`; 1–1,024 bytes, default 16 |
| `crypto.aes` | `{transformation,key,keyEncoding?,iv?,ivEncoding?,aad?,aadEncoding?,operation?,data,dataEncoding?,outputEncoding?}` |
| `crypto.hmac` | `{algorithm,key,keyEncoding?,data,dataEncoding?,outputEncoding?}`; `HmacSHA256` or `HmacSHA1` |
| `crypto.modPow` | `{baseHex,exponentHex,modulusHex,width?}` → lowercase hex, left-padded to `width` characters |
| `crypto.x25519` | `{scalar,scalarEncoding?,point?,pointEncoding?,outputEncoding?}`; both inputs 32 bytes, default point is basepoint 9 |
| `compression.gunzip` | `{data,dataEncoding?,outputEncoding?}`; decompressed bytes at most 8 MiB |

AES accepts `AES/CBC/PKCS5Padding`, `AES/ECB/PKCS5Padding`, or
`AES/GCM/NoPadding`, with 16/24/32-byte keys. CBC requires a 16-byte IV; GCM
requires a 12-byte IV and uses a 128-bit tag, with optional `aad`. ECB ignores
IV/AAD. `operation:"decrypt"` decrypts; the default is encryption.
`modPow` bounds base/modulus to 16,384 bits, exponent to 4,096 bits, modulus
to at least 2 bits, and output width to 0–32,768.

### Playback, queue and application services

| Method | Permission | Arguments → result |
|---|---|---|
| `playback.read` | `playbackRead` | `{}` → snapshot described below |
| `playback.play` | `playbackControl` | `{}` → `true` (resume) |
| `playback.pause` | `playbackControl` | `{}` → `true` |
| `playback.seek` | `playbackControl` | `{positionMs}` → `true` |
| `playback.select` | `playbackControl` | `{songId,positionMs?,playing?}` → `true`; song must exist in the queue |
| `playback.next` | `playbackControl` | `{}` → `true`; uses host auto-advance policy, not an arbitrary queue index |
| `playback.blockAutoAdvance` | `playbackControl` | `{blocked}` → `true`; only the owning plugin can release its block |
| `queue.replace` | `queueWrite` | `{songs,currentSongId,positionMs?,playing?}` → `true` |
| `notifications.toast` | `notifications` | `{message}` (at most 500 characters) → `true` |
| `clipboard.write` | `clipboard` | `{text}` (at most 16 Ki characters) → `true`; rejects if unavailable |
| `files.downloadCurrent` | `files.save` | `{}` → boolean after the host quality/save flow |
| `files.save` | `files.save` | `{url,headers?,filename?,mimeType?}` → boolean after the save flow |
| `webAuth.runScript` | `webAuth` | `{originUrl,script}` → completion string; see below |

`playback.read` returns `{currentSongId,queueSongIds,positionMs,durationMs,
playing,transitioning,seekRevision,endRevision}`. `currentSongId` is native
when the current song belongs to the caller, otherwise `""`. `queueSongIds`
contains native IDs only if the entire queue belongs to the caller; a mixed
queue returns `[]`. Clock/state/revisions still describe global playback.
`transitioning` covers stream resolution/startup; the revisions let the plugin
detect seeks and track-end events.

Playback controls operate on the host player, not an isolated plugin player.
`positionMs` defaults to 0 and is clamped to 0–31 days; `playing` defaults to
true. Selection IDs may be native or the caller's canonical song IDs (at most
2,048 characters at this RPC boundary); another provider's canonical ID is
rejected. `queue.replace` takes at most 500 Song objects and requires
`currentSongId` to name an entry. Validation failure leaves the old queue
unchanged. Auto-advance blocking is host coordination, not a cross-plugin lock;
another plugin can become the blocker. Disabling/removing the blocker releases
it and can resume a pending natural-end advance.

`files.downloadCurrent` acts on the currently playing song, even if it belongs
to another source. It closes the plugin dialog, opens the host quality sheet,
then uses the selected source's `resolveStream` and the host save dialog.
Local tracks use the host's local-copy path. It rejects when no current song
exists. Cancellation, unavailable saving, or a superseding download request can
resolve false; save/resolve failures can reject. Cover and lyrics are embedded
when available and supported by the host metadata writer.

`files.save` requires a granted initial URL and checks every redirect. It does
not require a separate `network` permission, but still needs declared domains
(which themselves require `network` in the manifest). URL, filename and MIME
inputs are limited to 16 Ki, 180 and 128 characters respectively. The host
sanitizes filenames and defaults an empty name to `audio`. Header intake keeps
at most 64 entries and skips empty names, CR/LF values and values over 8,192
characters. Downloads are capped at 1 GiB with 120-second transport timeouts.
It returns true on save success and false on cancellation/transfer failure;
missing platform UI or other setup failures can reject. The plugin never
receives the filesystem path.

`webAuth.runScript` takes a granted `originUrl` of at most 2,048 characters,
a nonempty `script` of at most 256 Ki characters, and returns the string passed
to `qplayerWebAuthDone(value)` (at most 64 Ki characters). HTTP origins require
`clearTextNetwork`; HTTPS is recommended. Each platform permits only one script
session at a time, with a 45-second timeout. Missing WebView support, concurrent
sessions, timeout and invalid results reject the call.

Android creates an empty HTML page with `originUrl` as its base URL, then runs
the script. Desktop first navigates to `originUrl`, then prepares the script
page at that origin. The script surface is hidden/noninteractive, not a login
window. Plugins own SDK loading, initialization and result parsing. Browser
subresource requests use the platform WebView, not `http.request`; they do not
inherit its per-request domain/method/DNS/redirect enforcement. Treat `webAuth`
as authority to run browser code at the granted origin, not as a restricted
HTTP helper.

## Login and credentials

The `login` capability requires `credentials`. It receives these operations:

| Operation | Additional arguments | Result |
|---|---|---|
| `methods` | `platform` (`android` or `desktop` from current app hosts; may be absent for older/service callers) | array of at most 8 methods |
| `begin` | `methodId` | LoginChallenge |
| `poll` | `challengeId` | LoginChallenge |
| `submit` | `methodId`, `credential` (string) | LoginChallenge |
| `logout` | none | `false` reports failure; any other return value reports success |

A method requires `id` matching `[a-z][a-z0-9._-]{0,63}`, `type` (`qr`, `web`,
`credential`, or `app`, normalized to lowercase), and nonempty `label` (4,096
characters maximum). Optional fields are `instructions` (64 Ki characters),
`appLabel` (4,096), `webUrl`, `cookieUrl`, `credentialCookieName`, and
`credentialLabel` (defaults to the host's translated credential label).
Nonempty web/cookie URLs must pass the network grant, even on non-web methods.

Advertise `app` only for Android. The app host must have an external-link
launcher to show that route; the parser accepting `app` does not make it usable
on desktop. `qr` and `app` use `begin` followed by polling, currently at
approximately 800 ms while no poll is in flight. `app` does not show a QR.
`credential` submits the user's text directly.

A `web` method also requires `webAuth`, HTTPS `webUrl`, HTTPS `cookieUrl`, and
`credentialCookieName` matching `[A-Za-z0-9_.-]{1,64}`. QPlayer opens the
interactive system WebView. When the named cookie exists, it submits the
**complete Cookie header for `cookieUrl`** through `submit`, not just that
cookie's value. The cookie name is the completion condition, not a response
filter. The plugin interprets and stores the credential with `credentials.put`;
the host does not validate a provider session on the plugin's behalf.

LoginChallenge is an object with required `status` (normalized to lowercase):

| Status | Host behavior |
|---|---|
| `waiting` | Show the waiting state and continue the poll flow |
| `scanned` | Show the scanned/confirmation state |
| `success` | Stop polling and refresh the account; optional `account` can supply the profile |
| `expired` | Restart QR/app login with `begin`; credential submission does not restart |
| `failed` | Stop polling and show `message`, or the host's default failure text |

Optional challenge fields are `id`, `methodId`, `message` (64 Ki characters),
`qrContent` (64 Ki characters), `qrImageBase64` (300,000 characters),
`appUrl`, `appLabel` (4,096 characters), `expiresAtMs` (nonnegative numeric
milliseconds, default 0), and `account`. Return a challenge `id` for polling:
the host retains a nonempty ID and sends it as `challengeId`.

`qrContent` is text that QPlayer encodes as a QR. `qrImageBase64` is raw Base64
PNG, not a data URL; the host checks Base64 decoding and the PNG signature.
If both are nonempty, the image wins. Use an image when the provider exposes no
recoverable QR text.

`appUrl` is opened only after a user tap. HTTPS links must pass the network
grant. Allowed custom schemes are `snssdk1128`, `snssdk2329`, `snssdk8478`,
`snssdk8663`, `luna`, `aweme`, `orpheus`, `orpheuswidget`, `wtloginmqq`,
`mqqapi`, `mqq`, and `qqmusic`. HTTP and other schemes are dropped without
discarding the challenge. `appLabel` supplies the button caption. Although the
challenge text limit is 64 Ki characters, the URL sanitizer drops app URLs over
16 Ki characters.

AccountProfile reads `loggedIn` (default false), native user `id` (optional),
`displayName` (4,096 characters), `avatarUrl`, `membershipTier` (0–1,000),
`level` (0–100,000), and `signature` (64 Ki characters). Omitted numeric fields
default to 0; disallowed avatar URLs become empty.

Credentials are AES-GCM encrypted under a shared installation data key, with
plugin/key identity checked in the encrypted envelope and hashed namespaced
paths. This is not a separate encryption key per plugin. Logging out should
delete the plugin's credential keys.

## Declarative dialogs

A plugin contributes no executable QML. It declares the `customUi` permission
and entries in manifest `ui`; QPlayer renders their dialogs with host components:

```json
{"id":"preferences","placement":"settings","label":"Preferences","icon":"tune"}
```

`placement: "settings"` adds a plugin-settings entry. `placement:
"playerAction"` adds the declared `label`/`icon` to the compact top bar and wide
navigation rail without teaching QPlayer what the feature does.

There can be at most 8 contributions. Contribution IDs are unique and match
`[a-z][a-z0-9._-]{0,63}`; labels are at most 40 characters (empty uses the plugin
name), and icons match `[a-z][a-z0-9_]{0,63}` (default `extension`).
Other syntactically valid placement strings can pass manifest validation but
do not establish a visible host entry.

Opening an entry invokes `handlers["ui.<contribution-id>"]` with
`{action:"open",payload:{inputs:{}}}`. A positive `refreshMs` re-invokes it
with `action:"refresh"` and an **empty** inputs map; unsent text is not included.
A button or switch sends its own ID as `action` and all current input values in
`payload.inputs` (text strings and switch booleans). Each successful reply
replaces the dialog description. Keep plugin state explicitly rather than
assuming an open/refresh call resubmits the form. Closing the dialog sends no
close handler.

The reply requires nonempty `title` (60 characters maximum) and a `body` array.
Optional `subtitle` is limited to 120 characters; `icon` defaults to `extension`
and uses the contribution icon pattern. `refreshMs` defaults to 0 (disabled);
otherwise use 500–60,000 milliseconds.

```js
return {
  title: "一起听", subtitle: "网易云音乐", icon: "group", refreshMs: 1500,
  body: [
    {type: "text", style: "title", center: true, text: "2 人正在一起听"},
    {type: "input", id: "invitation", placeholder: "邀请链接或房间 ID"},
    {type: "row", items: [
      {type: "button", id: "create", label: "创建房间", style: "filled"},
      {type: "button", id: "join", label: "加入房间", style: "outlined"}
    ]}
  ]
};
```

| Node | Fields and defaults |
|---|---|
| `text` | `text` (500 characters), `style` (`title`/`body`/`caption`, default `body`), `center` (false) |
| `error` | `text` (300 characters) |
| `input` | Required `id`; `placeholder` (60 characters), `value` (2,048), `secret` (false) |
| `switch` | Required `id`, nonempty `label` (120 characters); `desc` (500), `checked` (false), `enabled` (true) |
| `button` | Required `id`, nonempty `label` (24 characters); `style` (`filled`/`outlined`/`text`, default `filled`), `enabled` (true), `destructive` (false) |
| `row` | Required `items`: 1–3 buttons only, no nested rows |
| `spacer` | `height` 0–48, default 8 |

At most 10 top-level body nodes are allowed. IDs match
`[a-z][a-z0-9._-]{0,63}`; inputs and switches share one unique-ID namespace.
Button IDs are syntax-checked but are not included in that uniqueness check;
use distinct action IDs to avoid ambiguous dispatch. Optional text defaults
to `""`; boolean fields must be booleans. UI text rejects control characters,
including embedded newlines, so use separate nodes for separate blocks.

Unknown node types, invalid known field values, duplicate input/switch IDs,
and count/length violations fail normalization. Unknown extra object fields are
ignored, not a supported extension mechanism. The host offers no plugin colors,
images, markup, arbitrary geometry, or access to its QML scene.

A contribution's deprecated `source` is ignored by dialog execution. If an
older manifest still names a source path, package verification nevertheless
requires that file in the hash manifest. Omit `source` from new contributions.

### Background ticks

A plugin with `backgroundTimers` may define `handlers.backgroundTick`.
QPlayer invokes it with `{}` approximately once per second on the same actor.
It skips a tick while that plugin's previous tick Promise is unsettled and
discards the result after settlement. A rejected tick is logged; future ticks
are still scheduled. There is no precise timing, wakeup, foreground-service,
or process-survival guarantee.

Disabling/removing the plugin stops future tick scheduling and closes its
runtime. This does not promise rollback or cancellation of host operations
already issued. Dialog refresh is separate and is not a substitute for
background work when the dialog is closed.

## Migration and compatibility

QPlayer reads legacy numeric queue/cache records. Queue loading can assign
canonical IDs before a provider is installed; routing switches to the plugin
only after the matching enabled provider advertises `resolveStream`. Queue
saves use the new format and atomic writes. Legacy NetEase credentials can be
submitted to the installed plugin's credential login method. If migration fails,
the host retains the old credentials for retry; after the plugin confirms a
logged-in account, the host removes its old NetEase credentials to prevent a
later logout from restoring that session. Legacy metadata migration retains its
source file. These host migration paths do not give plugins access to arbitrary
legacy files or introduce another public credentials API.

QPlayer loads its known repository list from the bundled
`dev/t1m3/qplayer/plugin/plugin-catalog.json` resource. Each entry pins a GitHub
`owner/repo`, plugin ID, and publisher public key. It queries GitHub's latest
release endpoint and selects the first asset whose name ends in `.qplug`.
Publish a normal release with one `.qplug` asset and tag `v<manifest version>`
to avoid ambiguous assets/version display. The parser accepts a tag without
`v`; a tag/manifest version mismatch is logged, not rejected. The signed
manifest determines the installed version.

Plugins can release independently of QPlayer. Adding a catalog source requires
a host resource change and release, not a downloaded/user-editable catalog.
Publisher key rotation requires updating the host pin; already-released hosts
with the old key reject packages signed only by the new key. QPlayer does not
bundle or host the external plugin projects or their packages.

Installed sources can be enabled, disabled, selected as the primary source, or
removed from Settings. Removal stops the runtime and deletes executable package
files while retaining namespaced encrypted credentials/data for an intentional
reinstall; without an active runtime those namespaces are not exposed.
