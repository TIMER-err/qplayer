package dev.t1m3.qplayer.plugin;

import dev.t1m3.qplayer.media.AccountProfile;
import dev.t1m3.qplayer.media.MediaId;
import dev.t1m3.qplayer.media.MediaKind;
import dev.t1m3.qplayer.media.TogetherCommand;
import dev.t1m3.qplayer.media.TogetherRoom;
import dev.t1m3.qplayer.media.TogetherSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Standardized Listen Together ABI. Protocol details and room credentials stay in
 * the provider plugin; the host only coordinates canonical queue/transport state. */
public final class PluginTogetherService {
    private static final int MAX_LABEL_CHARS = 4096;
    private final PluginManager manager;
    private final CorePluginHostApi hostApi;

    public PluginTogetherService(PluginManager manager, CorePluginHostApi hostApi) {
        this.manager = manager;
        this.hostApi = hostApi;
    }

    public CompletableFuture<TogetherRoom> create(String provider) {
        return invoke(provider, "create", Collections.<String, Object>emptyMap())
                .thenApply(raw -> parseRoom(provider, raw));
    }

    public CompletableFuture<Map<String, Object>> status(String provider) {
        return invoke(provider, "status", Collections.<String, Object>emptyMap())
                .thenApply(raw -> map(raw, "together status"));
    }

    public CompletableFuture<TogetherRoom> currentRoom(String provider) {
        return status(provider).thenApply(value -> {
            if (!Boolean.TRUE.equals(value.get("inRoom")) || !(value.get("room") instanceof Map)) {
                return null;
            }
            return parseRoom(provider, value.get("room"));
        });
    }

    public CompletableFuture<Boolean> check(String provider, String roomId) {
        return invoke(provider, "check", singleton("roomId", nativeRoom(provider, roomId)))
                .thenApply(value -> Boolean.TRUE.equals(value));
    }

    public CompletableFuture<TogetherRoom> join(String provider, String roomId,
                                                 String inviterAccountId) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("roomId", nativeRoom(provider, roomId));
        args.put("inviterAccountId", nativeUser(provider, inviterAccountId));
        return invoke(provider, "join", args).thenApply(raw -> parseRoom(provider, raw));
    }

    public CompletableFuture<TogetherSnapshot> snapshot(String provider, String roomId) {
        return invoke(provider, "snapshot", singleton("roomId", nativeRoom(provider, roomId)))
                .thenApply(raw -> parseSnapshot(provider, raw));
    }

    public CompletableFuture<Boolean> reportPlaylist(String provider, String roomId,
                                                      String accountId, long version,
                                                      List<String> songIds) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("roomId", nativeRoom(provider, roomId));
        args.put("accountId", nativeUser(provider, accountId));
        args.put("version", version);
        List<String> nativeIds = new ArrayList<>();
        for (String value : songIds) {
            MediaId id = MediaId.parse(value).requireKind(MediaKind.SONG);
            if (!provider.equals(id.provider())) {
                throw new IllegalArgumentException("Together queue crosses providers");
            }
            nativeIds.add(id.nativeId());
        }
        args.put("songIds", nativeIds);
        return invoke(provider, "reportPlaylist", args).thenApply(this::truthy);
    }

    public CompletableFuture<Boolean> reportCommand(String provider, String roomId,
                                                     TogetherCommand command) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("roomId", nativeRoom(provider, roomId));
        args.put("type", command.type);
        args.put("progressMs", command.progressMs);
        args.put("playing", command.playing);
        args.put("sequence", command.sequence);
        args.put("formerSongId", nativeSong(provider, command.formerSongId));
        args.put("targetSongId", nativeSong(provider, command.targetSongId));
        return invoke(provider, "reportCommand", args).thenApply(this::truthy);
    }

    public CompletableFuture<Boolean> heartbeat(String provider, String roomId,
                                                 String songId, boolean playing,
                                                 long progressMs) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("roomId", nativeRoom(provider, roomId));
        args.put("songId", nativeSong(provider, songId));
        args.put("playing", playing);
        args.put("progressMs", progressMs);
        return invoke(provider, "heartbeat", args).thenApply(this::truthy);
    }

    public CompletableFuture<Boolean> end(String provider, String roomId) {
        return invoke(provider, "end", singleton("roomId", nativeRoom(provider, roomId)))
                .thenApply(this::truthy);
    }

    private CompletableFuture<Object> invoke(String provider, String operation,
                                             Map<String, Object> values) {
        Map<String, Object> args = new LinkedHashMap<>(values);
        args.put("operation", operation);
        return manager.invoke(provider, ProviderCapability.LISTEN_TOGETHER.wireName(), args);
    }

    private TogetherRoom parseRoom(String provider, Object raw) {
        Map<String, Object> value = map(raw, "Together room");
        TogetherRoom room = new TogetherRoom();
        String nativeRoom = requiredIdentifier(value.get("id"), "room id");
        room.id = MediaId.of(provider, MediaKind.ROOM, nativeRoom).toString();
        room.creatorAccountId = qualifyUser(provider, value.get("creatorAccountId"));
        room.effectiveDurationMs = boundedNumber(value.get("effectiveDurationMs"),
                0L, Long.MAX_VALUE, "Together effective duration");
        Object members = value.get("members");
        if (members instanceof List) {
            List<?> values = (List<?>) members;
            if (values.size() > 100) {
                throw new PluginExecutionException("Together room has too many members");
            }
            for (Object item : values) {
                Map<String, Object> member = map(item, "Together member");
                AccountProfile profile = new AccountProfile();
                profile.loggedIn = true;
                profile.id = qualifyUser(provider, member.get("id"));
                profile.displayName = boundedString(member.get("displayName"),
                        MAX_LABEL_CHARS, "Together member name");
                String avatar = optional(member.get("avatarUrl"));
                profile.avatarUrl = avatar.isEmpty() || hostApi.allowsReturnedUrl(provider, avatar)
                        ? avatar : "";
                room.members.add(profile);
            }
        }
        return room;
    }

    private TogetherSnapshot parseSnapshot(String provider, Object raw) {
        Map<String, Object> value = map(raw, "Together snapshot");
        TogetherSnapshot snapshot = new TogetherSnapshot();
        Object songs = value.get("songIds");
        if (songs instanceof List) {
            List<?> values = (List<?>) songs;
            if (values.size() > 5000) {
                throw new PluginExecutionException("Together snapshot has too many songs");
            }
            for (Object nativeId : values) {
                snapshot.songIds.add(MediaId.of(provider, MediaKind.SONG,
                        requiredIdentifier(nativeId, "Together song id")).toString());
            }
        }
        if (value.get("command") instanceof Map) {
            Map<String, Object> item = map(value.get("command"), "Together command");
            TogetherCommand command = new TogetherCommand();
            command.accountId = qualifyUser(provider, item.get("accountId"));
            command.type = optional(item.get("type")).toUpperCase();
            if (!("PLAY".equals(command.type) || "PAUSE".equals(command.type)
                    || "PROGRESS".equals(command.type) || "GOTO".equals(command.type)
                    || "NEXT".equals(command.type) || "PREV".equals(command.type))) {
                throw new PluginExecutionException("invalid Together command type");
            }
            command.formerSongId = qualifyOptional(provider, item.get("formerSongId"));
            command.targetSongId = qualifyOptional(provider, item.get("targetSongId"));
            command.progressMs = Math.max(0L, number(item.get("progressMs")));
            command.playing = Boolean.TRUE.equals(item.get("playing"));
            command.sequence = Math.max(0L, number(item.get("sequence")));
            snapshot.command = command;
        }
        return snapshot;
    }

    private static String qualifyOptional(String provider, Object raw) {
        String value = optional(raw);
        return value.isEmpty() || "0".equals(value) ? ""
                : MediaId.of(provider, MediaKind.SONG, value).toString();
    }

    private static String nativeSong(String provider, String raw) {
        if (raw == null || raw.isEmpty()) return "";
        MediaId id = MediaId.parse(raw).requireKind(MediaKind.SONG);
        if (!provider.equals(id.provider())) throw new IllegalArgumentException("song provider mismatch");
        return id.nativeId();
    }

    private static String qualifyUser(String provider, Object raw) {
        String value = nativeIdentifier(raw);
        return value.isEmpty() || "0".equals(value) ? ""
                : MediaId.of(provider, MediaKind.USER, value).toString();
    }

    private static String nativeUser(String provider, String raw) {
        return nativeOrLegacy(provider, MediaKind.USER, raw, true);
    }

    private static String nativeRoom(String provider, String raw) {
        return nativeOrLegacy(provider, MediaKind.ROOM, raw, false);
    }

    /** Accept raw ids in old invitation links, but never accept a canonical id
     * belonging to another provider or entity kind. */
    static String nativeOrLegacy(String provider, MediaKind kind, String raw,
                                 boolean optional) {
        if (raw == null || raw.isEmpty()) {
            if (optional) return "";
            throw new IllegalArgumentException(kind.wireName() + " id is empty");
        }
        MediaId parsed;
        try {
            parsed = MediaId.parse(raw);
        } catch (IllegalArgumentException notCanonical) {
            return MediaId.of(provider, kind, raw).nativeId();
        }
        parsed.requireKind(kind);
        if (!provider.equals(parsed.provider())) {
            throw new IllegalArgumentException(kind.wireName() + " provider mismatch");
        }
        return parsed.nativeId();
    }

    private boolean truthy(Object raw) { return !Boolean.FALSE.equals(raw); }

    private static Map<String, Object> singleton(String key, String value) {
        return Collections.<String, Object>singletonMap(key, value != null ? value : "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw, String label) {
        if (!(raw instanceof Map)) throw new PluginExecutionException(label + " must be an object");
        return (Map<String, Object>) raw;
    }

    private static String requiredIdentifier(Object raw, String label) {
        String value = nativeIdentifier(raw);
        if (value.isEmpty()) throw new PluginExecutionException(label + " is empty");
        return value;
    }

    private static String optional(Object raw) { return raw != null ? String.valueOf(raw) : ""; }

    private static String nativeIdentifier(Object raw) {
        if (raw instanceof Number) {
            Number value = (Number) raw;
            double number = value.doubleValue();
            return number == Math.rint(number) ? Long.toString(value.longValue()) : value.toString();
        }
        return optional(raw);
    }
    private static long number(Object raw) { return raw instanceof Number ? ((Number) raw).longValue() : 0L; }

    private static String boundedString(Object raw, int maximum, String label) {
        String value = optional(raw);
        if (value.length() > maximum) throw new PluginExecutionException(label + " is too long");
        return value;
    }

    private static long boundedNumber(Object raw, long minimum, long maximum, String label) {
        if (raw == null) return minimum;
        if (!(raw instanceof Number)) throw new PluginExecutionException(label + " must be a number");
        long value = ((Number) raw).longValue();
        if (value < minimum || value > maximum) {
            throw new PluginExecutionException(label + " is out of range");
        }
        return value;
    }
}
